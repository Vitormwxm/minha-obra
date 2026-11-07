package br.com.minhaobra.service;

public final class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    public ApiException(int status,String code,String message) { super(message);this.status=status;this.code=code; }
    public int status() { return status; }
    public String code() { return code; }
    public static ApiException unauthorized() { return new ApiException(401,"INVALID_CREDENTIALS","Usuário ou senha inválidos."); }
    public static ApiException forbidden() { return new ApiException(403,"FORBIDDEN","Você não tem permissão para esta ação."); }
    public static ApiException conflict() { return new ApiException(409,"VERSION_CONFLICT","Os dados foram atualizados. Recarregue e tente novamente."); }
}
