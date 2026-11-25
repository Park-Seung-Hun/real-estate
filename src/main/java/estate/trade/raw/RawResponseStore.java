package estate.trade.raw;

import java.io.IOException;

/** 원본 응답 파일 저장 포트. 응답 바이트를 그대로 gzip으로 압축해 키 위치에 저장한다. */
public interface RawResponseStore {

    /** 실패하면 예외를 던진다. 던진 예외의 메시지에 요청 주소나 인증키가 담기지 않아야 한다. */
    void save(String key, byte[] responseBody) throws IOException;
}
