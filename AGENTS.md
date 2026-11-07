# Instruções do projeto

- Plataforma independente do FocusLab: Java/Spring Boot, Angular,
  PostgreSQL, Kafka, FCM e Android Java/WebView. O nome Minha Obra é provisório.
- As funcionalidades financeiras e de contas aprovadas ainda serão
  implementadas; não apresentar a base de infraestrutura como app concluído.
- Compatibilidade de produção confirmada em 06/11/2026: Ubuntu 22.04.5,
  Docker Engine 29.1.3 e **docker-compose v2.24.5 standalone**.
  `docker compose` não existe nessa VPS. Scripts devem detectar plugin e
  standalone, sem atualizar a instalação automaticamente.
- A VPS tem 1 vCPU, cerca de 2 GiB RAM e 4 GiB swap. Builds acontecem no
  GitHub Actions. Limites de memória são parâmetros iniciais sujeitos a medição.
- Não publicar banco ou Kafka na internet. APIs e autorização ficam no backend.
- Backend com camadas explícitas: Controller → Service → Repository.
  Controllers validam contratos HTTP/DTOs, services contêm regras e transações,
  repositories tratam persistência. Controller não acessa repository diretamente.
- Não copiar assinatura privada Android, `.env`, credenciais, caches ou builds
  do projeto antigo para o Git. Preservar identidade e assinatura do APK.
- CI deve compilar os módulos reais. Não omitir jobs por ausência de arquivos
  nem apresentar testes pulados como validação completa.
- FocusLab: usuário autorizou descarte dos dados e retirada dos recursos
  identificados. O workflow de deploy antigo foi desativado. Isso não autoriza
  remover SSH, Certbot/TLS global, Python na porta 8443 ou recursos desconhecidos.
- Backups da nova plataforma serão manuais semanais, por escolha do usuário.
