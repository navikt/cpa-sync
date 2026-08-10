FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21@sha256:84cdaea917aeb5ea9e66a880e33ce209aed951df8074b9ba07b5d06fdfc3d9d2
COPY build/libs/app.jar /app/app.jar
WORKDIR /app
ENV TZ="Europe/Oslo"
USER nonroot
ENTRYPOINT ["java", "-jar", "app.jar"]
