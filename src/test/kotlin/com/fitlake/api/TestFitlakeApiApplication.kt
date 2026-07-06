package com.fitlake.api

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<FitlakeApiApplication>().with(TestcontainersConfiguration::class).run(*args)
}
