package com.devhc.aidevmob.frp

enum class FrpcKernel {
    GO,
    CPP;

    companion object {
        fun from(value: String?): FrpcKernel = entries.firstOrNull { it.name == value } ?: GO
    }
}
