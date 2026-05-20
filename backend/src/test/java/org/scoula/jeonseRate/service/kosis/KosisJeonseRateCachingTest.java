package org.scoula.jeonseRate.service.kosis;

import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.scoula.config.RedisConfig;
import org.scoula.jeonseRate.config.WebClientConfig;
import org.scoula.jeonseRate.dto.JeonseRateDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = KosisJeonseRateCachingTest.TestConfig.class)
@Log4j2
class KosisJeonseRateCachingTest {

    @Configuration
    @PropertySource("classpath:/application.properties")
    @Import({ WebClientConfig.class, RedisConfig.class })
    @ComponentScan(basePackages = "org.scoula.jeonseRate.service.kosis")
    static class TestConfig {}

    @Autowired
    private KosisJeonseRateService kosisService;

    @Test
    void KOSIS_동일_지역_반복_호출_응답시간_비교() {
        JeonseRateDTO dto = new JeonseRateDTO();
        dto.setBuildingType("아파트");
        Optional<JeonseRateDTO> opt = Optional.of(dto);
        String regionCode = "a7020203"; // 서울 송파구

        log.info("==========================================================");
        log.info("[ KOSIS 캐시 성능 테스트 ] 지역: 서울 송파구 / 주택유형: 아파트");
        log.info("==========================================================");

        long start1 = System.currentTimeMillis();
        List<Map<String, Object>> result1 = kosisService.fetchKosisData(opt, regionCode);
        long time1 = System.currentTimeMillis() - start1;
        log.info("1차 호출 완료: {}ms | 데이터 건수: {}건", time1, result1.size());

        long start2 = System.currentTimeMillis();
        List<Map<String, Object>> result2 = kosisService.fetchKosisData(opt, regionCode);
        long time2 = System.currentTimeMillis() - start2;
        log.info("2차 호출 완료: {}ms | 데이터 건수: {}건", time2, result2.size());

        log.info("----------------------------------------------------------");
        if (time2 < 50) {
            log.info("결과: Redis 캐시 히트  (1차: {}ms → 2차: {}ms)", time1, time2);
        } else {
            log.info("결과: 캐시 없음 - 외부 API 2회 호출  (1차: {}ms → 2차: {}ms)", time1, time2);
        }
        log.info("==========================================================");

        assertNotNull(result1);
        assertFalse(result1.isEmpty());
        assertEquals(result1.size(), result2.size());
    }
}