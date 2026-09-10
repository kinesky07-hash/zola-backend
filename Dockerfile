FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY . .

RUN chmod +x ./kotlin && ./kotlin build

ENV PORT=8080

EXPOSE 8080

CMD ["./kotlin", "run"]
