package estate.household.domain;

import java.time.Instant;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/** 가구 초대(data-model `invitation`). 토큰 원문은 저장하지 않고 SHA-256 32바이트만 둔다(결정 기록 0012). */
@Entity
@Table(name = "invitation")
public class Invitation {

    @Converter
    static class StatusConverter implements AttributeConverter<InvitationStatus, String> {
        @Override
        public String convertToDatabaseColumn(InvitationStatus status) {
            return status == null ? null : status.dbValue();
        }

        @Override
        public InvitationStatus convertToEntityAttribute(String dbValue) {
            for (InvitationStatus status : InvitationStatus.values()) {
                if (status.dbValue().equals(dbValue)) {
                    return status;
                }
            }
            return null;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "invitation_id_seq")
    @SequenceGenerator(name = "invitation_id_seq", sequenceName = "invitation_id_seq", allocationSize = 1)
    private Long id;

    private long householdId;

    private long createdBy;

    private byte[] tokenHash;

    @Convert(converter = StatusConverter.class)
    private InvitationStatus status;

    private Instant expiresAt;

    private Instant createdAt;

    protected Invitation() {
    }

    public Invitation(long householdId, long createdBy, byte[] tokenHash, Instant expiresAt, Instant createdAt) {
        this.householdId = householdId;
        this.createdBy = createdBy;
        this.tokenHash = tokenHash;
        this.status = InvitationStatus.PENDING;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public long getHouseholdId() {
        return householdId;
    }

    public long getCreatedBy() {
        return createdBy;
    }

    public byte[] getTokenHash() {
        return tokenHash;
    }

    public InvitationStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void accept() {
        this.status = InvitationStatus.ACCEPTED;
    }

    public void revoke() {
        this.status = InvitationStatus.REVOKED;
    }
}
