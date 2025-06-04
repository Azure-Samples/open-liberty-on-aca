# Monitoring a Liberty application deployed on Azure Container Apps with Azure Application Insights

This example demonstrates how to monitor a Open Liberty application with Azure Application Insights using OpenTelemetry.
It also provides instructions on how to deploy the application to Azure Container Apps.

## Prerequisites

You need the following tools to build and run this example:

- Prepare a local machine with Unix-like operating system installed - for example, Ubuntu, macOS, or Windows Subsystem for Linux.
- [Azure Subscription](https://azure.microsoft.com/free/)
- [Azure CLI 2.70.0+](https://learn.microsoft.com/en-us/cli/azure/install-azure-cli)
- [Git](https://git-scm.com/downloads)
- [Java 17+](https://learn.microsoft.com/java/openjdk)
- [Maven 3.9.8+](https://maven.apache.org/install.html)

## Creating Azure Resources

You need to create the following Azure resources:

- **Azure SQL Database**: It provides a relational database for your application.
- **Azure Log Analytics Workspace**: It backs up your application logs.
- **Azure Monitor Application Insights**: It provides application monitoring and diagnostics. Your application's telemetry data is sent to Application Insights for analysis.
- **Azure Container Registry**: It stores your application's Docker image.
- **Azure Container Apps Environment**: It hosts your Azure Container Apps resources.
- **Azure Container Apps**: It hosts your application as a container.

First, define the following variables in your bash shell by replacing the placeholders with your own values. They will be used throughout the example:

```bash
UNIQUE_VALUE=<your-unique-value>
LOCATION=<your-preferred-location, e.g., eastus2>
RESOURCE_GROUP_NAME=${UNIQUE_VALUE}rg
SQL_SERVER_NAME=${UNIQUE_VALUE}db
DB_NAME=demodb
DB_ADMIN=demouser
DB_ADMIN_PWD='super$ecr3t'$RANDOM$RANDOM
WORKSPACE_NAME=${UNIQUE_VALUE}log
APPINSIGHTS_NAME=${UNIQUE_VALUE}appinsights
REGISTRY_NAME=${UNIQUE_VALUE}reg
ACA_ENV=${UNIQUE_VALUE}env
ACA_OTEL_COLLECTOR=${UNIQUE_VALUE}otelcollector
ACA_LIBERTY_APP=${UNIQUE_VALUE}libertyapp
```

Next, create the resource group to host Azure resources:

```bash
az group create \
    --name $RESOURCE_GROUP_NAME \
    --location $LOCATION
```

Then, create the Azure resources in the resource group by following the steps below.

Create the Azure SQL Database server, database, and firewall rule that allows all Azure services to access the server:

```bash
az sql server create \
    --name $SQL_SERVER_NAME \
    --resource-group $RESOURCE_GROUP_NAME \
    --admin-user $DB_ADMIN \
    --admin-password $DB_ADMIN_PWD
az sql db create \
    --resource-group $RESOURCE_GROUP_NAME \
    --server $SQL_SERVER_NAME \
    --name $DB_NAME \
    --edition GeneralPurpose \
    --compute-model Serverless \
    --family Gen5 \
    --capacity 2
az sql server firewall-rule create \
    --resource-group $RESOURCE_GROUP_NAME \
    --server $SQL_SERVER_NAME \
    --name AllowAllAzureIps \
    --start-ip-address 0.0.0.0 \
    --end-ip-address 0.0.0.0
```

Create the Azure Log Analytics Workspace and Azure Monitor Application Insights:

```bash
az monitor log-analytics workspace create \
    --resource-group ${RESOURCE_GROUP_NAME} \
    --workspace-name ${WORKSPACE_NAME}
WORKSPACE_ID=$(az monitor log-analytics workspace show \
    --resource-group ${RESOURCE_GROUP_NAME} \
    --name ${WORKSPACE_NAME} \
    --query 'id' -o tsv)
az monitor app-insights component create \
    --resource-group ${RESOURCE_GROUP_NAME} \
    --app ${APPINSIGHTS_NAME} \
    --workspace ${WORKSPACE_ID} \
    --location ${LOCATION}
```

Create the Azure Container Registry and get the login server:

```bash
az acr create \
    --resource-group $RESOURCE_GROUP_NAME \
    --location ${LOCATION} \
    --name $REGISTRY_NAME \
    --sku Basic
LOGIN_SERVER=$(az acr show \
    --name $REGISTRY_NAME \
    --query 'loginServer' \
    --output tsv)
```

Create the Azure Container Apps environment with the existing Azure Log Analytics Workspace:

```bash
WORKSPACE_CUSTOMER_ID=$(az monitor log-analytics workspace show \
    --resource-group ${RESOURCE_GROUP_NAME} \
    --name ${WORKSPACE_NAME} \
    --query 'customerId' -o tsv)
WORKSPACE_KEY=$(az monitor log-analytics workspace get-shared-keys \
    --resource-group ${RESOURCE_GROUP_NAME} \
    --name ${WORKSPACE_NAME} \
    --query 'primarySharedKey' -o tsv)
az containerapp env create \
    --resource-group $RESOURCE_GROUP_NAME \
    --location $LOCATION \
    --name $ACA_ENV \
    --logs-workspace-id $WORKSPACE_CUSTOMER_ID \
    --logs-workspace-key $WORKSPACE_KEY
```

## Preparing the Application

Clone the repository and navigate to the `liberty-app-monitoring` directory:

```bash
git clone https://github.com/majguo/java-on-azure-samples.git
cd java-on-azure-samples/liberty-app-monitoring
```

## Building and Deploying the Application

In this example, the Liberty application is instrumented with MicroProfile OpenTelemetry feature that collects telemetry data and exports it to an OpenTelemetry Collector using the OpenTelemetry Protocol (OTLP). The OpenTelemetry Collector is configured to export the telemetry data to Azure Application Insights. For more information, see [OpenTelemetry Collector Agent Deployment](https://opentelemetry.io/docs/collector/deployment/agent/). The idea is similar to the [managed OpenTelemetry collector in Azure Container Apps](https://learn.microsoft.com/azure/container-apps/opentelemetry-agents?tabs=azure-cli%2Carm-example), the reason why it's not used here is that the Application Insights endpoint of the managed collector doesn't accept metrics, which is listed as a [known limitation](https://learn.microsoft.com/azure/container-apps/opentelemetry-agents?tabs=azure-cli%2Carm-example#known-limitations). 

You may also wonder if the OpenTelemetry java agent can be used, similar to the doc [Enable Azure Monitor OpenTelemetry for .NET, Node.js, Python, and Java applications](https://learn.microsoft.com/azure/azure-monitor/app/opentelemetry-enable?tabs=java). The benefit of using the java agent is that it can instrument the application without any code changes. However, the [Application Insights OpenTelemetry java agent](https://learn.microsoft.com/azure/azure-monitor/app/opentelemetry-enable?tabs=java#install-the-client-library) doesn't collect Open Liberty metrics, that's why it's not used in this example either.

The following steps show how to build and deploy the OpenTelemetry Collector and Liberty application to Azure Container Apps.

### Building and Deploying the OpenTelemetry Collector

Build Docker image for the OpenTelemetry Collector and push the Docker image to the Azure Container Registry:

```bash
az acr build -t otel-collector -r $REGISTRY_NAME -f otel-collector/Dockerfile .
```

Deploy the OpenTelemetry Collector to Azure Container Apps that pulls the Docker image from the Azure Container Registry. The collector is configured to export the telemetry data to Azure Application Insights:

```bash
export APPLICATIONINSIGHTS_CONNECTION_STRING=$(az monitor app-insights component show \
    --resource-group ${RESOURCE_GROUP_NAME} \
    --query '[0].connectionString' -o tsv)

az containerapp create \
    --resource-group $RESOURCE_GROUP_NAME \
    --name $ACA_OTEL_COLLECTOR \
    --environment $ACA_ENV \
    --image $LOGIN_SERVER/otel-collector \
    --registry-server $LOGIN_SERVER \
    --registry-identity system \
    --target-port 4318 \
    --secrets \
        appinsightsconnstring=${APPLICATIONINSIGHTS_CONNECTION_STRING} \
    --env-vars \
        APPLICATIONINSIGHTS_CONNECTION_STRING=secretref:appinsightsconnstring \
    --ingress 'internal' \
    --min-replicas 1
```

> [!NOTE]
> HTTP Port `4318` of OTEL Collector is specified for the internal ingress, so the Liberty application can communicate with the collector through the same virtual network. The gRPC port `4317` can't be used here because Azure Container Apps just supports HTTP/TCP port for ingress. If you want to use gRPC, you can deploy the OpenTelemetry Collector as a sidecar container of the Liberty application in the same Azure Container Apps, see the later section.

Wait for a while until the collector is deployed, started and running.

### Building and Deploying the Liberty Application

Build and package the Liberty application:

```bash
mvn clean package
```

Build Docker image for the Liberty application and push the Docker image to the Azure Container Registry:

```bash
az acr build -t javaee-cafe-monitoring:v1 -r $REGISTRY_NAME -f Dockerfile .
```

Deploy the Liberty application to Azure Container Apps that pulls the Docker image from the Azure Container Registry. The Liberty application is configured with the Azure SQL Database and OpenTelemetry Collector that you deployed earlier:

```bash
export DB_SERVER_NAME=$SQL_SERVER_NAME.database.windows.net
export DB_NAME=$DB_NAME
export DB_USER=$DB_ADMIN@$DB_SERVER_NAME
export DB_PASSWORD=$DB_ADMIN_PWD
export OTEL_SDK_DISABLED=false
export OTEL_SERVICE_NAME=javaee-cafe-monitoring

az containerapp create \
    --resource-group $RESOURCE_GROUP_NAME \
    --name $ACA_LIBERTY_APP \
    --environment $ACA_ENV \
    --image $LOGIN_SERVER/javaee-cafe-monitoring:v1 \
    --registry-server $LOGIN_SERVER \
    --registry-identity system \
    --target-port 9080 \
    --secrets \
        dbservername=${DB_SERVER_NAME} \
        dbname=${DB_NAME} \
        dbuser=${DB_USER} \
        dbpassword=${DB_PASSWORD} \
    --env-vars \
        DB_SERVER_NAME=secretref:dbservername \
        DB_NAME=secretref:dbname \
        DB_USER=secretref:dbuser \
        DB_PASSWORD=secretref:dbpassword \
        OTEL_EXPORTER_OTLP_ENDPOINT=http://${ACA_OTEL_COLLECTOR} \
        OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf \
        OTEL_SDK_DISABLED=${OTEL_SDK_DISABLED} \
        OTEL_SERVICE_NAME=${OTEL_SERVICE_NAME} \
    --ingress 'external' \
    --min-replicas 1
```

Wait for a while until the Liberty application is deployed, started and running. Then get the application URL and open it in a browser:

```bash
APP_URL=https://$(az containerapp show \
    --resource-group $RESOURCE_GROUP_NAME \
    --name $ACA_LIBERTY_APP \
    --query properties.configuration.ingress.fqdn -o tsv)
echo $APP_URL
```

You should see the Jakarta EE Cafe home page. Do interact with the application by adding, viewing, and removing coffees, which generates telemetry data and sends it to Azure Application Insights via the OpenTelemetry Collector.

In this section, you deployed two Azure Container Apps for the OpenTelemetry Collector and Liberty application separately. The Liberty application exports telemetry data to the OpenTelemetry Collector through OTLP/HTTP protocol, due to the fact that Azure Container Apps just supports HTTP/TCP port for ingress. The alternative way is to deploy the OpenTelemetry Collector as a sidecar container to the Liberty application container in the same Azure Container Apps, so they can communicate with OTLP/gRPC protocol that is more efficient than OTLP/HTTP protocol. For more information, see [Tutorial: Configure a sidecar container for a Linux app in Azure App Service](https://learn.microsoft.com/azure/app-service/tutorial-sidecar?tabs=portal).

### Deploying the OpenTelemetry Collector as a Sidecar Container of the Liberty Application

Deploy the Liberty application to Azure Container Apps:

```bash
az containerapp create \
    --resource-group $RESOURCE_GROUP_NAME \
    --name ${ACA_LIBERTY_APP}otel \
    --environment $ACA_ENV \
    --image $LOGIN_SERVER/javaee-cafe-monitoring:v1 \
    --registry-server $LOGIN_SERVER \
    --registry-identity system \
    --target-port 9080 \
    --secrets \
        dbservername=${DB_SERVER_NAME} \
        dbname=${DB_NAME} \
        dbuser=${DB_USER} \
        dbpassword=${DB_PASSWORD} \
        appinsightsconnstring=${APPLICATIONINSIGHTS_CONNECTION_STRING} \
    --env-vars \
        DB_SERVER_NAME=secretref:dbservername \
        DB_NAME=secretref:dbname \
        DB_USER=secretref:dbuser \
        DB_PASSWORD=secretref:dbpassword \
        OTEL_SDK_DISABLED=${OTEL_SDK_DISABLED} \
        OTEL_SERVICE_NAME=${OTEL_SERVICE_NAME}-otel \
    --ingress 'external' \
    --min-replicas 1
```

Retrieve the deployment YAML of the Liberty application:

```bash
az containerapp show \
    --resource-group $RESOURCE_GROUP_NAME \
    --name ${ACA_LIBERTY_APP}otel \
    --output yaml > liberty-app-otel.yaml
```

Edit the deployment YAML to add the OpenTelemetry Collector as a sidecar container. Here you use [`yq`](https://github.com/mikefarah/yq/?tab=readme-ov-file#install) to edit the YAML file:

```bash
export ACA_OTEL_COLLECTOR=$ACA_OTEL_COLLECTOR
export LOGIN_SERVER=$LOGIN_SERVER
yq -i '
    .properties.template.containers += [{
        "name": env(ACA_OTEL_COLLECTOR),
        "image": env(LOGIN_SERVER) + "/otel-collector",
        "imageType": "ContainerImage",
        "env": [{
            "name": "APPLICATIONINSIGHTS_CONNECTION_STRING",
            "secretRef": "appinsightsconnstring"
        }],
        "resources": {
            "cpu": 0.5,
            "memory": "1Gi",
            "ephemeralStorage": "2Gi"
        }
    }]
' liberty-app-otel.yaml
```

Update the deployment YAML with the sidecar container:

```bash
az containerapp update \
    --resource-group $RESOURCE_GROUP_NAME \
    --name ${ACA_LIBERTY_APP}otel \
    --yaml liberty-app-otel.yaml
rm -rf liberty-app-otel.yaml
```

Wait for a while until the Liberty application is updated, started and running. Then get the application URL and open it in a browser:

```bash
APP_OTEL_URL=https://$(az containerapp show \
    --resource-group $RESOURCE_GROUP_NAME \
    --name ${ACA_LIBERTY_APP}otel \
    --query properties.configuration.ingress.fqdn -o tsv)
echo $APP_OTEL_URL
```

Follow the same steps as before to interact with the application and generate telemetry data, the difference is that the Liberty application now sends telemetry data to the OpenTelemetry Collector sidecar container in the same host, through OTLP/gRPC protocol.

## Monitoring the Application

Open the Azure Portal and navigate to the Azure Monitor Application Insights resource you created earlier. You can monitor the Liberty application with different views backed by the telemetry data sent from the Liberty application. For example:

* Investigate > Application map: Shows the application components and their dependencies.
* Investigate > Failures: Shows the failures and exceptions in the application.
* Investigate > Performance: Shows the performance of the application.
* Monitoring > Metrics: Shows the metrics of the application including Open Liberty, JVM and application custom metrics.
* Monitoring > Logs: Shows the logs and traces of the application.

## Configure Health Probes using MicroProfile Health Check

The Liberty application is implemented with MicroProfile Health Check feature, which provides a way to check the health of the application. You can configure the health probes in Azure Container Apps to use the MicroProfile Health Check endpoints.

Retrieve the deployment YAML of the Liberty application:

```bash
az containerapp show \
    --resource-group $RESOURCE_GROUP_NAME \
    --name ${ACA_LIBERTY_APP} \
    --output yaml > liberty-app-health-probes.yaml
```

Edit the deployment YAML to add health probles. Here you use [`yq`](https://github.com/mikefarah/yq/?tab=readme-ov-file#install) to edit the YAML file:

```bash
yq -i '
    .properties.template.containers[0].probes = [{
        "type": "Liveness",
        "httpGet": {
            "path": "/health/live",
            "port": 9080
        },
        "initialDelaySeconds": 10,
        "periodSeconds": 5
    }, {
        "type": "Readiness",
        "httpGet": {
            "path": "/health/ready",
            "port": 9080
        },
        "initialDelaySeconds": 5,
        "periodSeconds": 3
    }, {
        "type": "Startup",
        "httpGet": {
            "path": "/health/started",
            "port": 9080
        },
        "initialDelaySeconds": 5,
        "periodSeconds": 3
    }]
' liberty-app-health-probes.yaml
```

Update the deployment YAML with the health probes:

```bash
az containerapp update \
    --resource-group $RESOURCE_GROUP_NAME \
    --name ${ACA_LIBERTY_APP} \
    --yaml liberty-app-health-probes.yaml
rm -rf liberty-app-health-probes.yaml
```

Wait for a while until the Liberty application is updated, started and running. Then get the application health URL and open it in a browser:

```bash
APP_HEALTH_URL=https://$(az containerapp show \
    --resource-group $RESOURCE_GROUP_NAME \
    --name ${ACA_LIBERTY_APP} \
    --query properties.configuration.ingress.fqdn -o tsv)/health
echo $APP_HEALTH_URL
```

You should see the similar output as below:

```json
{
  "status": "UP",
  "checks": [
    {
      "name": "CafeResource Readiness Check",
      "status": "UP",
      "data": {

      }
    },
    {
      "name": "CafeResource Liveness Check",
      "status": "UP",
      "data": {

      }
    },
    {
      "name": "CafeResource Startup Check",
      "status": "UP",
      "data": {

      }
    }
  ]
}
```

Additionally, check the health probes in the Azure Portal. Navigate to the Azure Container Apps resource you created earlier, and select **Application** > **Containers** > **Health probes**. You should see the health probes you configured, including **Liveness probes**, **Readiness probes**, and **Startup probes**.

## Clean Up

When you are done with the example, you can clean up the Azure resources by deleting the resource group:

```bash
az group delete \
    --name $RESOURCE_GROUP_NAME \
    --yes --no-wait
```

## Next Steps

You can learn more about Open Liberty, OpenTelemetry and Azure Monitor Application Insights from the following resources:

- [Collect logs, metrics, and traces with OpenTelemetry](https://openliberty.io/docs/latest/microprofile-telemetry.html)
- [OpenTelemetry Collector](https://opentelemetry.io/docs/collector/)
- [Azure Monitor Exporter](https://github.com/open-telemetry/opentelemetry-collector-contrib/tree/main/exporter/azuremonitorexporter)
- [Introduction to Application Insights with OpenTelemetry](https://learn.microsoft.com/azure/azure-monitor/app/app-insights-overview)
- [Deploy a Java application with Open Liberty on Azure Container Apps](https://learn.microsoft.com/azure/developer/java/ee/deploy-java-liberty-app-aca?tabs=in-bash)
- [Ingress in Azure Container Apps](https://learn.microsoft.com/azure/container-apps/ingress-overview)
- [Tutorial: Scale a container app](https://learn.microsoft.com/azure/container-apps/tutorial-scaling?tabs=bash)
- [Adding health reports to microservices](https://openliberty.io/guides/microprofile-health.html)
- [Health checks for microservices](https://openliberty.io/docs/latest/health-check-microservices.html)
