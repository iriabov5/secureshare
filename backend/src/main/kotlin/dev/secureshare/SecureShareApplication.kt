package dev.secureshare

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SecureShareApplication

fun main(args: Array<String>) {
    runApplication<SecureShareApplication>(*args)
}
