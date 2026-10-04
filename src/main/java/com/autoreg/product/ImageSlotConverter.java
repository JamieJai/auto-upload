package com.autoreg.product;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ImageSlotConverter implements AttributeConverter<ImageSlot, String> {

    @Override
    public String convertToDatabaseColumn(ImageSlot slot) {
        return slot == null ? null : slot.value();
    }

    @Override
    public ImageSlot convertToEntityAttribute(String value) {
        return value == null ? null
                : ImageSlot.of(value).orElseThrow(() -> new IllegalStateException("unknown slot " + value));
    }
}
