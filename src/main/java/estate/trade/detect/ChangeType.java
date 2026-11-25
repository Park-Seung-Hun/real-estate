package estate.trade.detect;

/** 거래 이력의 변경 종류. {@link #label()}이 {@code trade_history.change_type}에 저장되는 값이다. */
public enum ChangeType {

    NEW("신규"),
    CANCELED("해제"),
    REGISTERED("등기"),
    VANISHED("사라짐");

    private final String label;

    ChangeType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
