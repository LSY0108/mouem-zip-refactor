package org.scoula.jeonseRate.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.scoula.jeonseRate.domain.JeonseAnalysisVO;
import org.scoula.jeonseRate.dto.AddressInfoDTO;
import org.scoula.jeonseRate.dto.JeonseRateDTO;
import org.scoula.jeonseRate.enums.KosisRegionCode;
import org.scoula.jeonseRate.enums.KosisRegionDistrictCode;
import org.scoula.jeonseRate.enums.SafetyGrade;
import org.scoula.jeonseRate.mapper.JeonseAnalysisMapper;
import org.scoula.jeonseRate.service.address.AddressServiceImpl;
import org.scoula.jeonseRate.service.deal.DealSearchServiceImpl;
import org.scoula.jeonseRate.service.kosis.KosisJeonseRateServiceImple;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class LeaseDiagnosisService {

    private final AddressServiceImpl addressServiceImpl;
    private final DealSearchServiceImpl dealSearchServiceImpl;
    private final KosisJeonseRateServiceImple kosisJeonseRateServiceImple;
    private final JeonseAnalysisMapper jeonseAnalysisMapper;

    public ResponseEntity<?> analyzeLease(JeonseRateDTO request) {
        long t0 = System.currentTimeMillis();
        log.info("[시작] 전세가율 분석 요청 사용자ID={}, 등기부ID={}, 주소={}",
                request.getUserId(), request.getRegisterId(), request.getAddress());
        String keyword = request.getAddress();
        int registerId = request.getRegisterId();

        if (keyword == null || keyword.isBlank()) {
            log.warn("[실패] 전세가율 분석: 주소 파라미터 누락 (사용자ID={}, 등기부ID={})", request.getUserId(), registerId);
            return ResponseEntity.badRequest().body("주소가 없습니다.");
        }

        // 1. 주소 정보 조회
        AddressInfoDTO addressInfo = addressServiceImpl.lookupAddress(keyword);
        log.debug("주소 조회 완료: 시={}, 구/군={}, 법정동코드={}, 지번='{}', 건물명='{}'",
                addressInfo.getSiNm(), addressInfo.getSggNm(), addressInfo.getAdmCd(),
                addressInfo.getJibunAddr(), addressInfo.getBdNm());

        // 2. 최근 6개월 (yyyyMM) 목록 생성
        List<String> recentMonths = getRecentMonths(6);

        // 3. 법정동코드 앞 5자리
        String lawdCode = addressInfo.getAdmCd().substring(0, 5);

        // 4. 지번 주소 (예: 595-28)
        String jibun  = addressInfo.getJibunAddr();

        // 5. 건물명
        String buildingName = addressInfo.getBdNm();

        // 6. 전용 면적
        Double targetArea = request.getExcluUseAr();

        // 6. 해당 주소에 대한 매매가 평균 조회
        Optional<JeonseRateDTO> averageDealPriceOpt = dealSearchServiceImpl.getDealAmount(
                lawdCode, jibun, buildingName, recentMonths, targetArea
        );
        log.info("매매가 평균 조회 결과: 존재여부={}, 기준(법정동/지번/건물/면적)={}/{}/{}/{}",
                averageDealPriceOpt.isPresent(), lawdCode, jibun, buildingName, targetArea);

        // 조회된 매물이 없을 경우 메시지 반환
        if (averageDealPriceOpt.isEmpty()) {
            SafetyGrade grade = SafetyGrade.판단보류;
            JeonseAnalysisVO vo = new JeonseAnalysisVO();
            vo.setRegistryId(registerId);
            vo.setUserId(request.getUserId());
            vo.setExpectedSellingPrice(-1);
            vo.setDeposit(request.getJeonsePrice());
            vo.setJeonseRatio(-1);
            vo.setRegionAvgJeonseRatio(-1);
            vo.setJeonseRatioRating(grade);
            jeonseAnalysisMapper.insertJeonseAnalysis(vo);

            log.info("유사 매물 없음으로 판단보류 저장 완료: 사용자ID={}, 등기부ID={}",
                    request.getUserId(), registerId);

            long elapsed = System.currentTimeMillis() - t0;
            log.info("[완료] 전세가율 분석 종료(유사 매물 없음): 소요시간={}ms", elapsed);

            return ResponseEntity.ok(Map.of(
                    "status", "NO_MATCHING_DEAL",
                    "message", "해당 주소에 대한 유사 매물 정보를 찾을 수 없습니다.",
                    "grade", grade.name(),
                    "jeonsePrice", request.getJeonsePrice(),
                    "expectedSalePrice", -1,
                    "jeonseRate", -1,
                    "avgKosisRate", -1,
                    "deviation", -1,
                    "admCd", addressInfo.getAdmCd(),
                    "jibunAddr", addressInfo.getJibunAddr()
            ));
        }

        // 7. 전세가율 계산
        int averageDealPrice = averageDealPriceOpt.get().getAreaAVGPrice();            // 평균 매매가 (단위: 만원)
        int jeonse = request.getJeonsePrice();                          // 입력 전세 보증금
        double jeonseRate = ((double) jeonse / averageDealPrice) * 100; // 전세가율
        int roundedJeonseRate = (int) Math.round(jeonseRate);           // 전세가율 반올림하여 정수 처리
        log.info("전세가율 계산: 평균매매가={}만원, 보증금={}만원, 전세가율(반올림)={}%",

                averageDealPrice, jeonse, roundedJeonseRate);

        // 8. KOSIS 지역 코드 조회: 구 → 시 fallback 구조
        Optional<String> guCodeOpt = KosisRegionDistrictCode.findBySiDoAndSgg(addressInfo.getSiNm(), addressInfo.getSggNm())
                .map(KosisRegionDistrictCode::getCode);

        Optional<String> siCodeOpt = KosisRegionCode.findCodeFromRawRegionName(addressInfo.getSiNm());

        List<Map<String, Object>> kosisData = Collections.emptyList();

        // 8-1. 구 단위 코드 먼저 시도
        if (guCodeOpt.isPresent()) {
            log.debug("KOSIS 구 단위 코드 조회 시도: {}", guCodeOpt.get());
            kosisData = kosisJeonseRateServiceImple.fetchKosisData(averageDealPriceOpt, guCodeOpt.get());
        }

        // 8-2. 구 코드 결과가 없으면 시도 단위로 fallback
        if ((kosisData == null || kosisData.isEmpty()) && siCodeOpt.isPresent()) {
            log.debug("구 단위 실패 → 시 단위 코드로 재시도: {}", siCodeOpt.get());
            kosisData = kosisJeonseRateServiceImple.fetchKosisData(averageDealPriceOpt, siCodeOpt.get());
        }

        // 8-3. 둘 다 없으면 판단보류 처리
        if (kosisData == null || kosisData.isEmpty()) {
            SafetyGrade grade = SafetyGrade.판단보류;

            JeonseAnalysisVO vo = new JeonseAnalysisVO();
            vo.setRegistryId(registerId);
            vo.setUserId(request.getUserId());
            vo.setExpectedSellingPrice(averageDealPrice);
            vo.setDeposit(jeonse);
            vo.setJeonseRatio(roundedJeonseRate);
            vo.setRegionAvgJeonseRatio(-1);
            vo.setJeonseRatioRating(grade);
            jeonseAnalysisMapper.insertJeonseAnalysis(vo);

            log.info("KOSIS 통계 없음으로 판단보류 저장 완료: 사용자ID={}, 등기부ID={}",
                    request.getUserId(), registerId);
            long elapsed = System.currentTimeMillis() - t0;
            log.info("[완료] 전세가율 분석 종료(KOSIS 데이터 없음): 소요시간={}ms", elapsed);

            return ResponseEntity.ok(Map.of(
                    "status", "NO_KOSIS_DATA",
                    "message", "KOSIS 통계 데이터를 찾을 수 없습니다.",
                    "grade", grade.name(),
                    "jeonsePrice", jeonse,
                    "expectedSalePrice", averageDealPrice,
                    "jeonseRate", roundedJeonseRate,
                    "avgKosisRate", -1,
                    "deviation", -1,
                    "admCd", addressInfo.getAdmCd(),
                    "jibunAddr", addressInfo.getJibunAddr()
            ));
        }

        double avgKosisRate = kosisData.stream()
                .map(m -> Double.parseDouble(m.get("DT").toString()))
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);

        // 10. 편차 계산 및 등급 판별
        int deviation = roundedJeonseRate - (int) Math.round(avgKosisRate);
        SafetyGrade grade = SafetyGrade.fromDeviation(kosisData.isEmpty() ? null : deviation);
        log.info("지역 평균 전세가율={}, 편차={}, 등급={}",
                Math.round(avgKosisRate), deviation, grade);

        //11. DB 저장
        JeonseAnalysisVO vo = new JeonseAnalysisVO();
        vo.setRegistryId(registerId);                 // 등기부등본 번호
        vo.setUserId(request.getUserId());            // 유저 번호
        vo.setExpectedSellingPrice(averageDealPrice); // 평균 매매가
        vo.setDeposit(jeonse);                        // 사용자가 입력한 전세보증금
        vo.setJeonseRatio(roundedJeonseRate);         // 전세가율
        vo.setRegionAvgJeonseRatio((int) Math.round(avgKosisRate)); // 지역 평균 전세가율
        vo.setJeonseRatioRating(grade); // 등급

        jeonseAnalysisMapper.insertJeonseAnalysis(vo);

        log.info("서비스 전세가율 분석 결과 저장 완료: 등기부ID={}", registerId);

        long elapsed = System.currentTimeMillis() - t0;
        log.info("[완료] 서비스 전세가율 분석 요청 처리 종료: 소요시간={}ms", elapsed);

        return ResponseEntity.noContent().build();
    }

    //최근 N개월에 해당하는 "yyyyMM" 형식의 문자열 목록 반환
    private List<String> getRecentMonths(int count) {
        List<String> months = new ArrayList<>();
        LocalDate now = LocalDate.now();
        for (int i = 0; i < count; i++) {
            LocalDate target = now.minusMonths(i);
            months.add(String.format("%d%02d", target.getYear(), target.getMonthValue()));
        }
        return months;
    }

    @Cacheable(value = "diagnosis:result", key = "#registerId")
    public JeonseRateDTO getJeonseAnalysisResult(int registerId) {
        log.info("[시작] 전세가율 서비스 조회 요청: 등기부ID={}", registerId);
        Integer ratio  = jeonseAnalysisMapper.findJeonseRatioByRegisterId(registerId);

        if (ratio == null) {
            log.warn("[실패] 전세가율 조회 결과 없음: 등기부ID={}", registerId);
            return null;
        }

        JeonseRateDTO dto = new JeonseRateDTO();
        dto.setRegisterId(registerId);
        dto.setJeonseRate(ratio);

        log.info("[완료] 전세가율 서비스 조회 성공: 등기부ID={}, 전세가율={}", registerId, ratio);
        return dto;
    }
}
