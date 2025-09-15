### jdk complet ###
FROM openjdk:17-jdk-slim-bullseye
### for linux/amd64 ### Windows 11
#FROM openjdk:17-jdk@sha256:98f0304b3a3b7c12ce641177a99d1f3be56f532473a528fda38d53d519cafb13
### for linux/arm64/v8 ### Raspberry
#FROM openjdk:17-jdk@sha256:2fd12c42c12bf707f7ac0f5fa630ff9c59868dfc4428daaf34df9d82a0c5b101
#FROM arm64v8/openjdk:17-jdk-slim-bullseye

### Définir le répertoire de travail ###
WORKDIR /opt/app

# Installer SSH, ping, curl, etc...
RUN apt-get update && apt-get install -y \
    openssh-client \
    iputils-ping \
    curl \
    procps \
    gawk \
    coreutils \
    && rm -rf /var/lib/apt/lists/*

### Copier le fichier JAR dans le conteneur ###
COPY target/gateway-monitoring-api-0.0.1-SNAPSHOT.jar /gateway-monitoring.jar

### Exposer le port de l'application (si nécessaire) ###
EXPOSE 8081

### Commande pour démarrer l'application ###
#CMD ["java", "-jar", "/gateway-monitoring.jar"]
CMD ["java","-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005","-jar","/gateway-monitoring.jar"]
