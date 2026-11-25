package estate.trade.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import estate.support.CollectorFixture;
import estate.support.CollectorIntegrationTest;
import estate.support.FakeTradeApiServer;
import estate.support.FakeTradeApiServer.Reply;
import estate.support.MutableClock;
import estate.trade.collect.CollectionRun;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 날짜별로 모아 둔 원본 응답을 날짜순으로 반영한다(research R11). 실제 원본과 결과는 저장소 밖에만 두므로 환경 변수가 없으면
 * 건너뛴다. 실행 방법은 specs/003-trade-collection/quickstart.md의 "데이터 확인 기간 재생"이다.
 *
 * <p>{@code ESTATE_REPLAY_DIR} 아래의 날짜 폴더(YYYY-MM-DD)마다 {@code YYYYMM.xml}(여러 페이지면 {@code YYYYMM-pN.xml})과
 * 그날 실패한 달을 적은 {@code fetch.json}이 있다. 결과는 {@code ESTATE_REPLAY_OUT}에 탭으로 나눈 파일 셋으로 쓴다.
 */
@CollectorIntegrationTest
@EnabledIfEnvironmentVariable(named = "ESTATE_REPLAY_DIR", matches = ".+")
class TradeReplayTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    CollectorFixture fixture;
    @Autowired
    CollectionRun run;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    @AfterEach
    void reset() {
        fixture.reset();
    }

    @Test
    void replaysCollectedDays() throws IOException {
        Path root = Path.of(requiredEnv("ESTATE_REPLAY_DIR"));
        Path out = Files.createDirectories(Path.of(requiredEnv("ESTATE_REPLAY_OUT")));
        String lawdCd = requiredEnv("ESTATE_REPLAY_LAWD_CD");
        List<Path> days;
        try (Stream<Path> children = Files.list(root)) {
            days = children.filter(Files::isDirectory)
                    .filter(dir -> dir.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}"))
                    .sorted().toList();
        }
        assertThat(days).hasSizeGreaterThan(1);

        List<String> counts = new ArrayList<>(List.of("date\tdealYmd\tchangeType\tcount"));
        List<String> history = new ArrayList<>(
                List.of("date\tdealYmd\tchangeType\taptSeq\tdealDate\tfloor\tarea\tamount\tcanceled\tsameKeyCount"));
        List<String> ranges = new ArrayList<>(List.of("date\tdealYmd\tstatus\tfailureReason\trequests"));
        for (Path day : days) {
            String date = day.getFileName().toString();
            Instant at = LocalDate.parse(date).atTime(LocalTime.of(9, 0)).atZone(SEOUL).toInstant();
            Set<String> failed = failedMonths(day);
            // 시계를 멈추면 그날 반영한 이력의 observed_at이 모두 이 값이라 날짜별로 가를 수 있다
            clock.freezeAt(at);
            fixture.server().reset();
            fixture.server().fallback(request -> reply(day, failed, request));

            run.run(List.of(lawdCd));

            List<FakeTradeApiServer.Request> requests = fixture.server().requests();
            for (String dealYmd : requests.stream().map(FakeTradeApiServer.Request::dealYmd).distinct().sorted()
                    .toList()) {
                long sent = requests.stream().filter(request -> request.dealYmd().equals(dealYmd)).count();
                ranges.add(jdbc.sql("""
                        select status, coalesce(failure_reason, '') from fetch_range
                        where lawd_cd = :lawdCd and deal_ymd = :dealYmd""")
                        .param("lawdCd", lawdCd).param("dealYmd", dealYmd)
                        .query((rs, n) -> String.join("\t", date, dealYmd, rs.getString(1), rs.getString(2),
                                String.valueOf(sent)))
                        .single());
            }
            counts.addAll(jdbc.sql("""
                    select to_char(t.deal_date, 'YYYYMM'), h.change_type, count(*)
                    from trade_history h join trade t on t.id = h.trade_id
                    where h.observed_at = :at group by 1, 2 order by 1, 2""")
                    .param("at", Timestamp.from(at))
                    .query((rs, n) -> String.join("\t", date, rs.getString(1), rs.getString(2), rs.getString(3)))
                    .list());
            history.addAll(jdbc.sql("""
                    select to_char(t.deal_date, 'YYYYMM'), h.change_type, c.apt_seq, t.deal_date, t.floor,
                           t.exclusive_area, t.amount, t.canceled, t.same_key_count
                    from trade_history h join trade t on t.id = h.trade_id join complex c on c.id = t.complex_id
                    where h.observed_at = :at order by 1, 2, 3, 4, 5""")
                    .param("at", Timestamp.from(at))
                    .query((rs, n) -> {
                        StringBuilder line = new StringBuilder(date);
                        for (int column = 1; column <= 9; column++) {
                            line.append('\t').append(rs.getString(column));
                        }
                        return line.toString();
                    })
                    .list());
            if (day.equals(days.getFirst())) {
                // 첫날은 초기 적재라 모든 범위가 성공하고 이력이 없다
                assertThat(ranges).hasSize(61).allSatisfy(line -> assertThat(line).doesNotContain("실패"));
                assertThat(history).hasSize(1);
            }
            Files.write(out.resolve("counts.tsv"), counts);
            Files.write(out.resolve("history.tsv"), history);
            Files.write(out.resolve("ranges.tsv"), ranges);
        }
    }

    /** 그날 실패한 달과 파일이 없는 페이지는 연결을 끊는다. 데이터 확인 때의 네트워크 오류를 같은 모양으로 낸다. */
    private static Reply reply(Path day, Set<String> failed, FakeTradeApiServer.Request request) {
        Path file = day.resolve(request.dealYmd() + "-p" + request.pageNo() + ".xml");
        if (!Files.exists(file) && request.pageNo() == 1) {
            file = day.resolve(request.dealYmd() + ".xml");
        }
        if (failed.contains(request.dealYmd()) || !Files.exists(file)) {
            return Reply.disconnect();
        }
        try {
            return Reply.body(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> failedMonths(Path day) throws IOException {
        Set<String> failed = new HashSet<>();
        Path meta = day.resolve("fetch.json");
        if (Files.exists(meta)) {
            JsonNode entries = JsonMapper.shared().readTree(Files.readString(meta)).path("failed");
            entries.forEach(entry -> failed.add(entry.path("month").asString()));
        }
        return failed;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        assertThat(value).as(name).isNotBlank();
        return value;
    }
}
