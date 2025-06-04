FROM otel/opentelemetry-collector-contrib:0.122.0

COPY otel-collector/otel-collector-config.yaml /etc/otelcol-contrib/config.yaml

ENTRYPOINT ["/otelcol-contrib"]

CMD ["--config", "/etc/otelcol-contrib/config.yaml"]
