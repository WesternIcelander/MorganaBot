FROM jdk17
COPY target/MorganaBot.jar /MorganaBot.jar
ENTRYPOINT ["java", "-jar", "/MorganaBot.jar"]
