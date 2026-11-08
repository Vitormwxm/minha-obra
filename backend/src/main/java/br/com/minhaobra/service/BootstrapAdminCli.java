package br.com.minhaobra.service;

import br.com.minhaobra.repository.AccountRepository;
import java.io.Console;
import java.util.Arrays;
import java.util.Properties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

public final class BootstrapAdminCli {
    private BootstrapAdminCli() {}
    public static boolean requested(String[] args) {return args.length>0 && "--bootstrap-admin".equals(args[0]);}
    public static int run(String[] args) {
        if(args.length!=2) {System.err.println("Uso: java -jar application.jar --bootstrap-admin <username>");return 2;}
        Console console=System.console();
        if(console==null) {System.err.println("Use um terminal interativo (docker exec -it); a senha não será aceita por argumento ou variável de ambiente.");return 2;}
        System.out.println("A senha provisória deve ter exatamente 8 dígitos de 0 a 9.");
        char[] first=console.readPassword("Senha provisória do administrador: ");
        char[] confirmation=console.readPassword("Repita a senha provisória: ");
        if(first==null || confirmation==null || !Arrays.equals(first,confirmation)) {
            wipe(first);wipe(confirmation);System.err.println("As senhas não conferem. Nenhuma conta foi alterada.");return 2;
        }
        try {
            String url=required("SPRING_DATASOURCE_URL");
            if(!url.startsWith("jdbc:postgresql:")) throw new IllegalStateException();
            var source=new DriverManagerDataSource(url,required("SPRING_DATASOURCE_USERNAME"),required("SPRING_DATASOURCE_PASSWORD"));
            Properties properties=new Properties();properties.setProperty("currentSchema","minhaobra");source.setConnectionProperties(properties);
            var repository=new AccountRepository(new JdbcTemplate(source));
            var service=new BootstrapAdminService(repository,new BCryptPasswordEncoder(12),new TransactionTemplate(new DataSourceTransactionManager(source)));
            service.create(args[1],new String(first));
            System.out.println("Administrador criado. Troque a senha no primeiro acesso e configure o autenticador.");return 0;
        } catch(ApiException error) {System.err.println(error.getMessage());return 1;}
        catch(RuntimeException error) {System.err.println("Não foi possível criar o administrador. Confira a migração e a configuração privada do banco; nenhuma senha foi exibida.");return 1;}
        finally {wipe(first);wipe(confirmation);}
    }
    private static String required(String name) {String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException();return value;}
    private static void wipe(char[] value) {if(value!=null)Arrays.fill(value,'\0');}
}
