package org.celanwang.mio.align;

import java.util.Map;

/** Read-only assembly of a probe request. Failure must surface as UNAVAILABLE, not an empty profile. */
public interface ProbeRequests {
    ProbeRequest forCall(String sessionId, String callId, String toolName, Map<String, Object> input,
                         boolean readOnlyDeclared, String intent);
}
