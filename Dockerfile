FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21@sha256:96ba5fe25650d4f8ccad4a85a1d44dc5f4f759f8ab7bf59695be298f3bc18817
COPY build/libs/app.jar /app/app.jar
WORKDIR /app
ENV TZ="Europe/Oslo"
USER nonroot
ENTRYPOINT ["java", "-jar", "app.jar"]
