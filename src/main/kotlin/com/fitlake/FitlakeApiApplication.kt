package com.fitlake

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class FitlakeApiApplication

fun main(args: Array<String>) {
	runApplication<FitlakeApiApplication>(*args)
}
