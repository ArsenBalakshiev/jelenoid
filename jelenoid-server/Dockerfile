# Используем официальный образ Maven с JDK 21 (дистрибутив Eclipse Temurin)
FROM maven:3.9.6-eclipse-temurin-21

  # Устанавливаем рабочую директорию внутри контейнера
WORKDIR /app

  # Копируем pom.xml, чтобы кешировать зависимости
COPY pom.xml .
RUN mvn dependency:go-offline -B

  # Затем копируем остальной исходный код
COPY src ./src

  # Команда для запуска приложения в режиме разработки
CMD ["mvn", "spring-boot:run"]