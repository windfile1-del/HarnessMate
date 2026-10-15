package com.example.mixed.app

import androidx.annotation.NonNull

class AppKotlinWorkflow {
    @NonNull
    fun processWorkflow(@NonNull input: String): String {
        return "app-kt-wf:[$input]"
    }

    @NonNull
    fun buildFullReport(@NonNull coordinator: AppJavaCoordinator, @NonNull raw: String): String {
        val pipelineResult = coordinator.executeCoordinatorPipeline(this, raw)
        val leaf = coordinator.getCoordinatorLeafToken()
        return "REPORT:[$pipelineResult | leaf=$leaf]"
    }
}
