package com.elipair.church.domain.position;

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
 * 직분(목사·장로·권사…). 권한과 독립된 회원의 선택 속성(스펙 §3.2/§5.3).
 * soft delete·낙관락·작성자 없이 created_at만 갖는 마스터 데이터라 BaseTimeEntity를 상속한다.
 */
@Entity
@Table(
        name = "positions",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_positions_church_name",
                        columnNames = {"church_id", "name"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Position extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    private Position(String name, Integer sortOrder) {
        this.name = name;
        this.sortOrder = sortOrder;
    }

    public static Position of(String name, Integer sortOrder) {
        return new Position(name, sortOrder);
    }

    /** 각 인자 null은 미변경(부분 수정). */
    public void update(String name, Integer sortOrder) {
        if (name != null) {
            this.name = name;
        }
        if (sortOrder != null) {
            this.sortOrder = sortOrder;
        }
    }
}
