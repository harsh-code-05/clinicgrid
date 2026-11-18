package com.harsh.hospitalmanagement.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoRoomResponse {
    private Long appointmentId;
    private String roomId;
    private String userRole;
    private String userName;
    private String peerName;
    private boolean doctorVerified;
    private boolean canJoin;
    private String statusMessage;
    private List<IceServerConfig> iceServers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IceServerConfig {
        private List<String> urls;
        private String username;
        private String credential;
    }
}
