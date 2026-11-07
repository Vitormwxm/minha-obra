FROM nginx:stable-alpine@sha256:bb8f722b4be005fcd7827816a136ab779fd40185d85b5171be915cc7fc0575c7
COPY frontend/dist/minha-obra/browser/ /usr/share/nginx/html/
COPY infra/nginx/default.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 80 443
