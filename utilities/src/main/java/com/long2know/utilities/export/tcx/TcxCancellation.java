package com.long2know.utilities.export.tcx;

public interface TcxCancellation {
    TcxCancellation NONE = () -> false;

    boolean isCancellationRequested();
}
