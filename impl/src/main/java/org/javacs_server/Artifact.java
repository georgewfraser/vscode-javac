package org.javacs_server;

import java.util.Objects;

class Artifact {
    final String groupId, artifactId, version;

    Artifact(String groupId, String artifactId, String version) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
    }

    static Artifact parse(String id) {
        String[] parts = id.split(":");

        if (parts.length == 3) return new Artifact(parts[0], parts[1], parts[2]);
        else if (parts.length == 5) return new Artifact(parts[0], parts[1], parts[3]);
        else throw new IllegalArgumentException(id + " is not properly formatted artifact");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        var artifact = (Artifact) o;
        return Objects.equals(groupId, artifact.groupId)
                && Objects.equals(artifactId, artifact.artifactId)
                && Objects.equals(version, artifact.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId, version);
    }

    @Override
    public String toString() {
        return String.format("%s:%s:%s", groupId, artifactId, version);
    }
}
