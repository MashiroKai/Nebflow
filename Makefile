.PHONY: compile run assembly clean install

compile:
	sbt compile

run:
	sbt run

assembly:
	sbt assembly

clean:
	sbt clean

install: assembly
	@echo "Installing nebscala..."
	@mkdir -p $(HOME)/.local/bin
	@cp target/scala-3.5.2/nebscala-assembly-1.0.0.jar $(HOME)/.local/bin/nebscala.jar
	@echo '#!/bin/sh' > $(HOME)/.local/bin/nebscala
	@echo 'exec java -jar "$(HOME)/.local/bin/nebscala.jar" "$$@"' >> $(HOME)/.local/bin/nebscala
	@chmod +x $(HOME)/.local/bin/nebscala
	@echo "Installed to $(HOME)/.local/bin/nebscala"
	@echo "Make sure $(HOME)/.local/bin is in your PATH"
