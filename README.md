# Tracktr - Advanced Fleet Tracking Solution

**Tracktr is fundamentally built upon the powerful [Traccar](https://www.traccar.org/) open-source GPS tracking system.** We have taken the core Traccar source code and significantly extended it to create a proprietary, feature-rich platform. While Tracktr retains the robust device communication and high-performance Java backend of the original Traccar project, it introduces a completely modernized Web User Interface, dedicated mobile applications, and advanced fleet management capabilities.

## Relationship to Traccar

Because Tracktr is built directly upon the Traccar engine, it inherits:
* **Massive Device Compatibility:** Out-of-the-box support for hundreds of GPS tracking protocols and thousands of device models (including Teltonika, Ruptela, CalAmp, Meitrack, and many more) developed by the Traccar community.
* **High-Performance Architecture:** The same Netty-based, asynchronous, event-driven network application capabilities that make Traccar incredibly scalable.
* **Core Functionality:** Reliable real-time tracking, geofencing logic, and foundational reporting.

**What Tracktr adds:**
* A completely redesigned, intuitive web dashboard. **(Please note: The custom frontend source code resides in a separate repository: [`tracktr-web`](./tracktr-web))**
* Dedicated, branded mobile applications for Android and iOS.
* Extended enterprise features and custom integrations not found in the base Traccar repository.

## Features

* **Real-time GPS Tracking:** Monitor your fleet, assets, or personal devices in real-time with high accuracy.
* **Modern Web UI:** A completely redesigned, intuitive web dashboard for managing devices, geofences, and reports. *Note: The UI codebase is located in the separate [`tracktr-web`](./tracktr-web) repository.*
* **Geofencing & Alerts:** Create complex polygon or circular geofences and receive instant notifications (via Email, SMS, Telegram, Firebase, Web Push, etc.) for events like entering/exiting zones, overspeeding, or device disconnection.
* **Comprehensive Reporting:** Generate detailed reports for trips, stops, route history, and summary statistics, with export options to CSV, GPX, and KML formats.
* **Event Forwarding:** Seamlessly integrate with external systems by forwarding real-time position and event data via JSON, Kafka, or custom webhooks.

## Architecture & Technology Stack

Tracktr maintains the reliable technology stack of its upstream source for the backend:
* **Backend:** Java (JDK 11+ recommended)
* **Networking:** Netty framework
* **Build System:** Gradle
* **Database:** Supports multiple relational databases via an abstraction layer (MySQL, PostgreSQL, H2).
* **Frontend:** Maintained independently in `./tracktr-web`.

## Getting Started

### Prerequisites
* Java Development Kit (JDK) 11 or higher
* A supported database (e.g., MySQL, PostgreSQL, H2 for testing)
* The frontend repository (`tracktr-web`) set up and running for UI access.

### Building the Project
Tracktr uses the Gradle build system. To compile the project and build the executable JAR, run the following command from the root directory:

#### On Linux/macOS:
```bash
./gradlew assemble
