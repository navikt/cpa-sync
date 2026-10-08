FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21@sha256:4a2340345d8af038d57c0eb81638accb07023b3a06ff301d35689107c6ae7fb7
COPY build/libs/app.jar /app/app.jar
WORKDIR /app
ENV TZ="Europe/Oslo"
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75.0"
USER nonroot
ENTRYPOINT ["java", "-jar", "app.jar"]
