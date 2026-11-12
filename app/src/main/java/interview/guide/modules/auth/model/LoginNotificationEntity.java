package interview.guide.modules.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "login_notifications")
@Getter
@Setter
@NoArgsConstructor
public class LoginNotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auth_session_id", nullable = false, unique = true)
    private AuthSessionEntity authSession;

    /** 站内通知不再保存手机号快照；存量行由 V8 迁移清空。 */
    @Column(name = "phone_snapshot", length = 20)
    private String phoneSnapshot;

    @Column(name = "device_label", length = 255)
    private String deviceLabel;

    @Column(name = "login_ip", length = 45)
    private String loginIp;

    @Column(name = "login_at", nullable = false)
    private LocalDateTime loginAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LoginNotificationStatus status = LoginNotificationStatus.PENDING;

    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /** 站内通知已读时间；为空表示未读。 */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (loginAt == null) {
            loginAt = createdAt;
        }
    }
}
