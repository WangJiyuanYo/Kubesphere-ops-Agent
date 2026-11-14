package com.lanhao.ops.diagnosis;

import com.lanhao.ops.domain.Model.*;

public interface DiagnosticNarrator {
    Diagnosis explain(String facts, Action allowedAction);
}
