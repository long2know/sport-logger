package com.long2know.sportlogger.publishing.endurain;

import com.long2know.sportlogger.connector.endurain.EndurainConnectionConfig;
import com.long2know.sportlogger.connector.endurain.EndurainHttpsTransport;
import com.long2know.sportlogger.connector.endurain.EndurainRemoteActivityId;
import com.long2know.sportlogger.connector.endurain.EndurainUploadResult;
import com.long2know.sportlogger.connector.endurain.TcxUploadArtifact;
import com.long2know.sportlogger.security.EndurainApiKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class HttpsEndurainTransportGateway
        implements EndurainTransportGateway {
    private final EndurainHttpsTransport transport;

    public HttpsEndurainTransportGateway(EndurainHttpsTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public UploadOutcome upload(
            EndurainConnectionConfig config,
            TcxUploadArtifact artifact,
            EndurainApiKey credential) {
        EndurainUploadResult result =
                transport.upload(config, artifact, credential);
        if (result.state() == EndurainUploadResult.State.DELIVERED) {
            List<Long> remoteIds = new ArrayList<>();
            for (EndurainRemoteActivityId remote : result.remoteActivities()) {
                if (!remote.normalizedBaseUrl()
                        .equals(config.endpoint().normalizedBaseUrl())
                        || !remote.connectionIdentity()
                        .equals(config.connectionIdentity())) {
                    return UploadOutcome.failure(
                            UploadOutcome.State.UNKNOWN_DELIVERY,
                            "REMOTE_SCOPE_MISMATCH",
                            result.httpStatus());
                }
                remoteIds.add(remote.activityId());
            }
            return UploadOutcome.delivered(remoteIds);
        }
        if (result.state() == EndurainUploadResult.State.RATE_LIMITED) {
            return UploadOutcome.rateLimited(
                    result.retryAfter(),
                    result.httpStatus());
        }
        if (result.state() == EndurainUploadResult.State.UNKNOWN_DELIVERY
                && result.retryAfter() != null) {
            return UploadOutcome.unknownRateLimited(
                    result.retryAfter(),
                    result.httpStatus());
        }
        return UploadOutcome.failure(
                UploadOutcome.State.valueOf(result.state().name()),
                result.reason().name(),
                result.httpStatus());
    }
}
