FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21@sha256:eab74be57dad5fc072791fda0f0558889158ef5793c233387e66a773f5416a03
COPY build/libs/app.jar /app/app.jar
WORKDIR /app
ENV TZ="Europe/Oslo"
ENV JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=75.0"
USER nonroot
ENTRYPOINT ["java", "-jar", "app.jar"]
