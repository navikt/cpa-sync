FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21@sha256:ecbb6ead37ef09e89b360e260c8e2b697597dcc5e072aca6d43cd81fc629165f
COPY build/libs/app.jar /app/app.jar
WORKDIR /app
ENV TZ="Europe/Oslo"
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75.0"
USER nonroot
ENTRYPOINT ["java", "-jar", "app.jar"]
