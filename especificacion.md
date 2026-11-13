Quiero desarrollar un servicio web REST poder leer documentos legales (Facturas, Notas de débito y Notas de crédito) y poder extraer campos de la cabecera y del detalle para facilitar la carga manual de esa información en distintos sistemas. Realizarlo preferenctemente con el stack de Java salvo que no sea conveniente.
Los requisitos definidos son los siguientes:
- Generar un servicio web que publique una API para poder extraer información de esos documentos legales.
- El principal servicio debe poder pasar como parámetros el documento, y el listado de campos a buscar en el documento para extraer la información. El cómo se indican esos campos puede tener varias opciones según el formato del documento. Por ejemplo el número de factura puede ser "Número factura", "Número de factura", "Nro. factura" o "Factura N°).
- El servicio devuelve en formato json para cada campo solicitado el valor encontrado y una descripción. El campo descripción será utilizado para aclarar cualquier situación que sea relevante. Por ejemplo: dato no encontrado, había más de una opción y se utilizó el campo encontrado X dentro del documento.
- El formato de los documentos es principalmente PDF. Puede haber casos en donde sean imágenes.
- Cada cliente procesa como máximo 100 documentos mensuales.
- Buscamos tener una solución flexible que utilice IA para el procesamiento de los documentos.
- Si utilizamos IA, el principal problema es la confidencialidad de la información, ya que son documentos con información legal y sensible.
- La prioridad es evaluar soluciones donde se pueda utilizar entornos de IA locales en la propia infraestructura, sin necesidad de utilizar servicios externos. Se podría evaluar una alternativa como un servicio dedicado al procesamiento de documentos de los proveedores más conocidos.
- Priorizar soluciones open source y del stack tecnológico de Java.
- Lo importante de este servicio es que sea reutilizable y que no hay lógica de negocio definida.
- Tener en cuenta los costos del uso de IA para el volumen de procesamiento presentado.
