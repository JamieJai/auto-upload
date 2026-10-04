package com.autoreg.channel.adapter.naver;

/** 패키지 내부 변환 함수를 다른 테스트 패키지에서 쓰기 위한 통로 */
public final class NaverCommerceClientAccess {

    private NaverCommerceClientAccess() {}

    public static byte[] toJpeg(byte[] webp) {
        return NaverCommerceClient.toJpeg(webp, "t.webp");
    }
}
