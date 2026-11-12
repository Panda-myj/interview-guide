package interview.guide.modules.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "user_devices",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_user_device_key",
        columnNames = {"user_id", "device_key_hash"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class UserDeviceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "device_key_hash", nullable = false, length = 64)
    private String deviceKeyHash;

    @Column(name = "user_agent_hash", nullable = false, length = 64)
    private String userAgentHash;

    @Column(name = "user_agent_label", length = 255)
    private String userAgentLabel;

    @Column(name = "first_seen_ip", length = 45)
    private String firstSeenIp;

    @Column(name = "last_seen_ip", length = 45)
    private String lastSeenIp;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        firstSeenAt = now;
        lastSeenAt = now;
    }
}
