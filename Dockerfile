FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21@sha256:ed0912daf3c28130e190f316c04153800bbcf95e50f74ba0345f8aae9c359fa7
COPY build/libs/app.jar /app/app.jar
WORKDIR /app
ENV TZ="Europe/Oslo"
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75.0"
USER nonroot
ENTRYPOINT ["java", "-jar", "app.jar"]
