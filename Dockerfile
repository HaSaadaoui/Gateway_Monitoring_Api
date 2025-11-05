### jdk complet ###
FROM amazoncorretto:17-alpine


### Définir le répertoire de travail ###
WORKDIR /opt/app

# Copy source code
COPY . .

# Run Maven and skip tests
RUN chmod +x mvnw && ./mvnw clean install -DskipTests

RUN cp ./target/gateway-monitoring-api-0.0.1-SNAPSHOT.jar /gateway-monitoring.jar

### Exposer le port de l'application (si nécessaire) ###
EXPOSE 8081

### Commande pour démarrer l'application ###
CMD ["java", "-jar", "/gateway-monitoring.jar"]
#CMD ["java","-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005","-jar","/gateway-monitoring.jar"]
