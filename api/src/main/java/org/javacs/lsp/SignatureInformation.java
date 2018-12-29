package org.javacs.lsp;

import java.util.List;

public class SignatureInformation {
    public String label;
    public MarkupContent documentation;
    public List<ParameterInformation> parameters;
}
