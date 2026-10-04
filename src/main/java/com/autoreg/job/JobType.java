package com.autoreg.job;

public enum JobType {
    /** 비어 있는 문구를 LLM 으로 채운다 */
    GENERATE,
    /** 채널 1곳에 상품을 등록한다 (channel_listing 1행) */
    REGISTER
}
