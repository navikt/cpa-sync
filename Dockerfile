FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21@sha256:b6252714c5b59256b44f51fa5adcf0b1725d2daf425e3824236efbea1a0d97d4
COPY build/libs/app.jar /app/app.jar
WORKDIR /app
ENV TZ="Europe/Oslo"
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75.0"
USER nonroot
ENTRYPOINT ["java", "-jar", "app.jar"]
