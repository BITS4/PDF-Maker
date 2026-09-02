package com.example.pdfmaker

internal data class SmartScanControlsState(
    val scanMode: ScanMode,
    val idCardSide: IdCardCaptureSide,
    val idCardStep: IdCardStep,
    val isCapturing: Boolean,
    val capturedDocs: List<CapturedDoc>,
)
