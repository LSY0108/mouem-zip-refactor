package org.scoula.jeonseRate.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.scoula.jeonseRate.enums.SafetyGrade;


@Getter
@Setter
@ToString
public class JeonseRateDTO {
    private int userId;             // 유저 번호
    private int registerId;         // 등기부등본 번호
    private String address;         // 사용자가 입력한 주소
    private int jeonsePrice;        // 전세 보증금 (단위: 만원)
    private String buildingType;    // 건물 유형
    private int areaAVGPrice;       // 지역 평균 매매가
    private SafetyGrade grade;      // 등급
    private int jeonseRate;         // 전세가율
    private int regionAvgJeonseRate;// 지역 평균 전세가율

    // 선택값(프론트에서 줄 수도/안 줄 수도)
    private Double excluUseAr;
}
