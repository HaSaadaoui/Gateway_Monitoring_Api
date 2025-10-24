### jdk complet ###
FROM openjdk:17-jdk-slim-bullseye

### Définir le répertoire de travail ###
WORKDIR /opt/gateway

# Copy source code
COPY . .

# Run Maven and skip tests
RUN chmod +x mvnw && ./mvnw clean install -DskipTests

### Exposer le port de l'application (si nécessaire) ###
EXPOSE 8081

### Commande pour démarrer l'application ###
CMD ["java", "-jar", "/gateway-monitoring.jar"]
#CMD ["java","-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005","-jar","/gateway-monitoring.jar"]
