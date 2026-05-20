package org.scoula.jeonseRate.service.kosis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.scoula.jeonseRate.dto.JeonseRateDTO;
import org.scoula.jeonseRate.enums.HouseTypeCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class KosisJeonseRateServiceImple implements KosisJeonseRateService{

    private final WebClient webClient;


    @Value("${kosis.key}")
    private String apiKey;

    private static final String BASE_URL = "https://kosis.kr/openapi/Param/statisticsParameterData.do";

    private static final String ORG_ID = "408";
    private static final String TBL_ID = "DT_30404_N0006_R1";
    private static final String ITM_ID = "rate";



    @Cacheable(
            value = "kosis:rate",
            key = "#averageDealPriceOpt.get().buildingType + '_' + #objL2",
            condition = "#averageDealPriceOpt.isPresent() && #objL2 != null",
            unless = "#result.isEmpty()"
    )
    public List<Map<String, Object>> fetchKosisData(Optional<JeonseRateDTO> averageDealPriceOpt, String objL2) {
        Optional<String> objL1 = HouseTypeCode.fromName(averageDealPriceOpt.get().getBuildingType());

        if (objL1.isEmpty() || objL2 == null || objL2.isBlank()) {
            return List.of(); // KOSIS 파라미터 매핑 실패
        }

        String response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("kosis.kr")
                        .path("/openapi/Param/statisticsParameterData.do")
                        .queryParam("method", "getList")
                        .queryParam("apiKey", apiKey)
                        .queryParam("orgId", ORG_ID)
                        .queryParam("tblId", TBL_ID)
                        .queryParam("itmId", ITM_ID)
                        .queryParam("objL1", objL1)
                        .queryParam("objL2", objL2)
                        .queryParam("format", "json")
                        .queryParam("jsonVD", "Y")
                        .queryParam("prdSe", "M")           // 주기: 월별
                        .queryParam("newEstPrdCnt", "3")    // 최근 데이터 개수 → 3개월
                        .build())
                .retrieve()
                .bodyToMono(byte[].class)
                .map(bytes -> new String(bytes, Charset.forName("UTF-8")))
//                .doOnNext(raw -> System.out.println("응답 원문 (EUC-KR 해석): " + raw))
                .block();


        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(response, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new RuntimeException("KOSIS 응답 파싱 실패", e);
        }
    }
}