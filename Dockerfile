FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY . .

RUN ./kotlin build

ENV PORT=8080

EXPOSE 8080

CMD ["sh", "-c", "java -jar build/tasks/_backend_jarJvm/backend-jvm.jar"]
