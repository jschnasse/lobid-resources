#!/bin/bash
# Description: Tests JSON examples against schemas
# Prerequisites: install 'ajv':$  npm install -g ajv-cli

for version in "draft"; do
	echo "Testing version: $version"
	ajv test -s ../schemas/resource.json -r "../schemas/*.json" -d "../resources/jsonld/*.json" --valid
#		ajv test -s ../test/schemas/resource.json -r "../test/schemas/*.json" -d "invalid/*.json" --invalid
done

if [ $? -eq 0 ]
then
	echo -e "All tests \033[0;32mPASSED\033[0m\n"
	else
		echo -e "Test \033[0;31mFAILED\033[0m\n"
fi
