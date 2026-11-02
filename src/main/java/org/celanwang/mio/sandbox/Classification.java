package org.celanwang.mio.sandbox;

/**
 * One cell of the risk × runtime matrix.
 * {@code credential} names the proxy material this op may receive; null means no credential egress.
 */
public record Classification(RiskClass risk, RuntimeKind runtime, String credential) {
    public Classification {
        if (risk == null || runtime == null) {
            throw new IllegalArgumentException("classification requires risk and runtime");
        }
    }
}
