# Open Liberty/WebSphere Liberty on Azure Container Apps Samples

## Overview

[Azure Container Apps](https://azure.microsoft.com/products/container-apps) is a fully managed Kubernetes-based application platform that helps you deploy apps from code or containers without orchestrating complex infrastructure. Build heterogeneous modern apps or microservices with unified centralized networking, observability, dynamic scaling, and configuration for higher productivity. Design resilient microservices with full support for [Dapr](https://go.microsoft.com/fwlink/?linkid=2216423) and dynamic scaling powered by [KEDA](https://go.microsoft.com/fwlink/?linkid=2217018).

[Open Liberty](https://openliberty.io) is an IBM Open Source project that implements the Eclipse MicroProfile specifications and is also Java/Jakarta EE compatible. Open Liberty is fast to start up with a low memory footprint and supports live reloading for quick iterative development. It is simple to add and remove features from the latest versions of MicroProfile and Java/Jakarta EE. Zero migration lets you focus on what's important, not the APIs changing under you.

[WebSphere Liberty](https://www.ibm.com/cloud/websphere-liberty) architecture shares the same code base as the open sourced Open Liberty server runtime, which provides additional benefits such as low-cost experimentation, customization and seamless migration from open source to production.

This repository contains sample projects for deploying Java applications with Open Liberty/WebSphere Liberty on Azure Container Apps.
These sample projects show how to use various features in Open Liberty/WebSphere Liberty and how to integrate with different Azure services.
Below table shows the list of samples available in this repository.

| Sample                           | Description                                | Guide                            |
|----------------------------------|--------------------------------------------|----------------------------------|
| [`java-app`](java-app) | Deploy a simple Java application with Open Liberty/WebSphere Liberty on Azure Container Apps. | [Deploy a Java application with Open Liberty or WebSphere Liberty on Azure Container Apps](https://learn.microsoft.com/azure/developer/java/ee/deploy-java-liberty-app-aca) |
