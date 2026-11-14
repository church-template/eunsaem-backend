package com.elipair.church.domain.tag;

import com.elipair.church.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 글로벌 태그(예배·선교·봉사…). 권한·콘텐츠와 독립된 마스터 데이터로,
 * soft delete·낙관락·작성자 없이 created_at만 갖는 BaseTimeEntity를 상속하고 물리 삭제한다(스펙 §5.11).
 */
@Entity
@Table(
        name = "tags",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_tags_church_name",
                        columnNames = {"church_id", "name"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tag extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    private Tag(String name) {
        this.name = name;
    }

    public static Tag create(String name) {
        return new Tag(name);
    }

    /** name null은 미변경(부분 수정). */
    public void rename(String name) {
        if (name != null) {
            this.name = name;
        }
    }
}
