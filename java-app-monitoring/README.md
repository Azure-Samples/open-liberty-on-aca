# Monitoring a Liberty application deployed on Azure Container Apps using Azure Application Insights

This example demonstrates how to monitor a Open Liberty or WebSphere Liberty application using Azure Application Insights.
It also provides instructions on how to deploy the application to Azure Container Apps.

## Prerequisites

You need the following tools to build and run this example:

- Prepare a local machine with Unix-like operating system installed - for example, Ubuntu, macOS, or Windows Subsystem for Linux.
- [Azure Subscription](https://azure.microsoft.com/free/)
- [Azure CLI](https://learn.microsoft.com/en-us/cli/azure/install-azure-cli)
- [Git](https://git-scm.com/downloads)
- [Java 17+](https://learn.microsoft.com/java/openjdk)
- [Maven 3.9.8+](https://maven.apache.org/install.html)
- [Docker](https://www.docker.com/products/docker-desktop)

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
ACA_NAME=${UNIQUE_VALUE}aca
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

Clone the repository and navigate to the `java-app-monitoring` directory:

```bash
git clone https://github.com/Azure-Samples/open-liberty-on-aca.git
cd open-liberty-on-aca/java-app-monitoring
```

The [`src/main/liberty/config/jvm.options`](src/main/liberty/config/jvm.options) file contains the Azure Application Insights OpenTelemetry Java agent configuration:

```
-javaagent:/liberty/usr/shared/resources/applicationinsights-agent.jar
```

The specified Java agent is attached to the Liberty runtime when the application is started. The agent dynamically injects bytecode that instruments the application to collect telemetry data and send it to Azure Application Insights. 

## Building and Deploying the Application

Build and package the application:

```bash
mvn clean package
```

Containerize the application by building the Docker image:

```bash
docker buildx build --platform linux/amd64 -t javaee-cafe-monitoring:v1 --pull --file=Dockerfile .
```

Sign in to the Azure Container Registry and push the Docker image to the registry:

```bash
docker tag javaee-cafe-monitoring:v1 $LOGIN_SERVER/javaee-cafe-monitoring:v1
az acr login --name $REGISTRY_NAME
docker push $LOGIN_SERVER/javaee-cafe-monitoring:v1
```

Deploy the application to Azure Container Apps that pulls the application image from the Azure Container Registry. The application is configured with the Azure SQL Database, Azure Application Insights, and other environment variables:

```bash
export DB_SERVER_NAME=$SQL_SERVER_NAME.database.windows.net
export DB_NAME=$DB_NAME
export DB_USER=$DB_ADMIN@$DB_SERVER_NAME
export DB_PASSWORD=$DB_ADMIN_PWD
export APPLICATIONINSIGHTS_CONNECTION_STRING=$(az monitor app-insights component show \
    --resource-group ${RESOURCE_GROUP_NAME} \
    --query '[0].connectionString' -o tsv)
export OTEL_SERVICE_NAME=javaee-cafe-monitoring

az containerapp create \
    --resource-group $RESOURCE_GROUP_NAME \
    --name $ACA_NAME \
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
        APPLICATIONINSIGHTS_CONNECTION_STRING=secretref:appinsightsconnstring \
        OTEL_SERVICE_NAME=${OTEL_SERVICE_NAME} \
    --ingress 'external' \
    --min-replicas 1
```

Wait for a while until the application is deployed, started and running. Then get the application URL and open it in a browser:

```bash
APP_URL=https://$(az containerapp show \
    --resource-group $RESOURCE_GROUP_NAME \
    --name $ACA_NAME \
    --query properties.configuration.ingress.fqdn -o tsv)
echo $APP_URL
```

You should see the Jakarta EE Cafe home page. Do interact with the application by adding, viewing, and removing coffees, which generates telemetry data and sends it to Azure Application Insights.

## Monitoring the Application

Open the Azure Portal and navigate to the Azure Monitor Application Insights resource you created earlier. You can monitor the application with different views backed by the telemetry data sent from the application. For example:

* Investigate > Application map: Shows the application components and their dependencies.
* Investigate > Live metrics: Shows the live metrics of the application.
* Investigate > Failures: Shows the failures and exceptions in the application.
* Investigate > Performance: Shows the performance of the application.
* Monitoring > Metrics: Shows the metrics of the application.
* Monitoring > Logs: Shows the logs and traces of the application.

## Clean Up

When you are done with the example, you can clean up the Azure resources by deleting the resource group:

```bash
az group delete \
    --name $RESOURCE_GROUP_NAME \
    --yes --no-wait
```

## Resources

You can learn more about Azure Application Insights, Open Liberty, and OpenTelemetry from the following resources:

- [Enable Azure Monitor OpenTelemetry for .NET, Node.js, Python, and Java applications](https://learn.microsoft.com/azure/azure-monitor/app/opentelemetry-enable?tabs=java)
- [Collect logs, metrics, and traces with OpenTelemetry](https://openliberty.io/docs/latest/microprofile-telemetry.html)
- [OpenTelemetry Java instrumentation documentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/supported-libraries.md)
