package com.autoreg.job;

public enum JobType {
    /** 비어 있는 문구를 LLM 으로 채운다 */
    GENERATE,
    /** 도매처 원문에서 사실값(가격·색상·사이즈·소재·실측)을 뽑아 빈칸을 채운다 */
    EXTRACT,
    /** 상품 사진의 워터마크를 지운다 (리터치 포함, 사진당 20초 안팎이라 워커에서) */
    WATERMARK,
    /** 채널 1곳에 상품을 등록한다 (channel_listing 1행) */
    REGISTER
}
