package br.com.activation.application;

import br.com.activation.domain.ActivationRequest;

public interface ActivationProvider {
    Outcome activate(ActivationRequest request);

    record Outcome(boolean successful, boolean retryable, String code) {
        public static Outcome success() { return new Outcome(true, false, null); }
        public static Outcome permanent(String code) { return new Outcome(false, false, code); }
        public static Outcome temporary(String code) { return new Outcome(false, true, code); }
    }
}
