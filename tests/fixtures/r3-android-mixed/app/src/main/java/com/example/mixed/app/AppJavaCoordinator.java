package com.example.mixed.app;

import androidx.annotation.NonNull;
import com.example.mixed.library.LibraryJavaHelper;
import com.example.mixed.library.LibraryKotlinService;

public class AppJavaCoordinator {
    private final LibraryKotlinService libKtService = new LibraryKotlinService();
    private final LibraryJavaHelper libJavaHelper = new LibraryJavaHelper();

    @NonNull
    public String executeCoordinatorPipeline(@NonNull AppKotlinWorkflow workflow, @NonNull String input) {
        String stage1 = libKtService.formatMessage(input);
        String stage2 = workflow.processWorkflow(stage1);
        String stage3 = libJavaHelper.assembleStatus(libKtService);
        return stage2 + " | " + stage3;
    }

    @NonNull
    public String getCoordinatorLeafToken() {
        return "COORDINATOR_TERMINAL_LEAF";
    }
}
