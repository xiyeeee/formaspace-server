package com.xiye.formaspace.generation.parameter;

import com.xiye.formaspace.common.exception.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class GenerationSnapshots {
    private final ObjectMapper json;

    public String encode(Object snapshot) {
        String value = json.writeValueAsString(snapshot);
        if (value.length() > 4096) { throw new BusinessException(ErrorCode.INVALID_GENERATION_PARAMETERS); }
        return value;
    }

    public String fingerprint(String canonicalRequest) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    public <T> T decode(String snapshot, Class<T> type) {
        if (snapshot == null) { return null; } // Historical V1 rows had no parameter snapshots.
        if (snapshot.length() > 4096) { throw new IllegalStateException("Invalid persisted generation snapshot"); }
        return json.readValue(snapshot, type);
    }
}
