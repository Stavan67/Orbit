package com.orbit.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConjunctionResult {
    private Integer primaryNoradId;
    private Integer secondaryNoradId;
    private LocalDateTime tca;
    private Double missDistance;
    private Double relativeVelocity;
    private Double primaryAltitude;
    private Double secondaryAltitude;

    @Override
    public String toString() {
        return String.format(
                "Conjunction[Primary=%d, Secondary=%d, TCA=%s, Miss=%.1fm, RelVel=%.1fm/s, Alt1=%.1fkm, Alt2=%.1fkm]",
                primaryNoradId,
                secondaryNoradId,
                tca,
                missDistance,
                relativeVelocity,
                primaryAltitude,
                secondaryAltitude
        );
    }
}