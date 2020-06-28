FROM openjdk:11.0.6-jre-slim

LABEL maintainer="Alexey Izmaylov <aleksey.izmaylove@gmail.com>"

COPY target/tenant-security-1.0.0-SNAPSHOT.jar /tenant-security/app.jar

RUN chmod -R 755 /tenant-security/

EXPOSE 8080

ENTRYPOINT ["java","-Xms128m","-Xmx128m","-Xss512k",\
            "-XX:ReservedCodeCacheSize=64M",\
            "-XX:MaxMetaspaceSize=128m",\
            "-XX:InitialBootClassLoaderMetaspaceSize=32M",\
            "-XX:MinMetaspaceExpansion=1M",\
            "-XX:MaxMetaspaceExpansion=8M",\
            "-jar",\
            "/tenant-security/app.jar"]
