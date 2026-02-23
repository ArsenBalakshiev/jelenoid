package com.balakshievas.tests.selenide;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static com.codeborne.selenide.Condition.*;
import static com.codeborne.selenide.Selenide.*;

public class SelenideShowcaseTest extends BaseSelenideTest {

    @Test
    @DisplayName("Элегантная работа с формами")
    void shouldInteractWithForm() {
        open("https://httpbin.org/forms/post");

        $("input[name='custname']").setValue("John Doe");
        $("input[name='custtel']").setValue("+123456789");
        $("input[value='small']").click();

        $("button").click();

        $("body").shouldHave(
                text("John Doe"),
                text("+123456789"),
                text("small")
        );
    }

    @Test
    @DisplayName("Удобная загрузка файлов в контейнер Jelenoid")
    void shouldUploadFileEasily() throws Exception {
        open("https://the-internet.herokuapp.com/upload");

        File tempFile = File.createTempFile("selenide-upload", ".txt");
        tempFile.deleteOnExit();

        $("#file-upload").uploadFile(tempFile);
        $("#file-submit").click();

        $("#uploaded-files").shouldHave(text(tempFile.getName()));
    }
}
