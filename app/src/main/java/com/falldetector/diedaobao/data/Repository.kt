package com.falldetector.diedaobao.data

class Repository(private val database: AppDatabase) {
    val contactDao = database.contactDao()
    val fallEventDao = database.fallEventDao()
}